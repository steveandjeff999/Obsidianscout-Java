/**
 * Node tests for the Local AI assistant (no browser, no model).
 * Run: node --test src/test/js/ai-assistant.test.mjs   (also wired into `gradlew test` via the jsTest task when Node is installed)
 *
 * Builds a small synthetic event, loads it through ai-data.js with a mocked API, then checks routing,
 * that every routed tool and suggestion chip exists, that every tool runs, and the number guardrail.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const staticJs = path.resolve(here, "../../main/resources/static/js");
const evalSet = JSON.parse(readFileSync(path.join(here, "assistant-eval.json"), "utf8"));

// ------------------------------------------------------------------ synthetic event behind a mocked API

const EVENT = "2026test";
const TEAMS = evalSet.teams;
let seed = 7;
const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);

const config = {
    fields: [
        { id: "sec_auto", type: "section", label: "Autonomous", phase: "auto" },
        { id: "auto_scored", type: "counter", label: "Auto Fuel Scored", pointsPer: 1 },
        { id: "sec_tele", type: "section", label: "Teleop", phase: "teleop" },
        { id: "teleop_scored", type: "counter", label: "Teleop Fuel", pointsPer: 1 },
        { id: "sec_end", type: "section", label: "Endgame", phase: "endgame" },
        { id: "climb", type: "select", label: "Climb", options: [{ value: "n", points: 0 }, { value: "l1", points: 10 }, { value: "l3", points: 30 }] },
        { id: "comments", type: "textarea", label: "Comments" }
    ]
};
const qualConfig = { fields: [{ id: "driver", type: "rating", label: "Driver Rating" }, { id: "notes", type: "textarea", label: "Notes" }] };

const matches = [];
const entries = [];
const qual = [];
for (let m = 1; m <= 20; m++) {
    const six = TEAMS.slice().sort(() => rand() - 0.5).slice(0, 6);
    const played = m <= 14;
    matches.push({
        matchKey: `${EVENT}_qm${m}`, eventKey: EVENT, compLevel: "qm", setNumber: 1, matchNumber: m, label: `QM ${m}`,
        redTeams: six.slice(0, 3).map((t) => `frc${t}`), blueTeams: six.slice(3).map((t) => `frc${t}`),
        redScore: played ? 80 : null, blueScore: played ? 70 : null, scheduledTime: 1790000000 + m * 480
    });
    if (!played) continue;
    six.forEach((team, i) => {
        const skill = 1 - TEAMS.indexOf(team) / TEAMS.length;
        const data = {
            eventKey: EVENT, matchKey: `${EVENT}_qm${m}`, matchNumber: m, targetTeamNumber: team,
            auto_scored: Math.round(skill * 9 + rand() * 2), teleop_scored: Math.round(skill * 30 + rand() * 5),
            climb: skill > 0.6 ? "l3" : skill > 0.3 ? "l1" : "n", comments: i % 2 ? "fast cycles, good driver" : "tipped over once"
        };
        entries.push({ id: `e${m}-${team}`, targetTeamNumber: team, eventKey: EVENT, matchKey: data.matchKey, matchNumber: m, data, createdAt: "2026-10-01T00:00:00Z" });
        qual.push({ id: `q${m}-${team}`, targetTeamNumber: team, eventKey: EVENT, matchNumber: m, data: { driver: Math.round(skill * 5), notes: "solid defense" }, createdAt: "2026-10-01T00:00:00Z" });
    });
}

const responses = {
    "/api/settings": { settings: { year: 2026, eventKey: EVENT } },
    "/api/config": config,
    "/api/qual-config": qualConfig,
    "/api/pit-config": { fields: [] },
    // EPA / OPR / xP per team, so the external metrics exist (OPR deliberately does not follow EPA's order).
    [`/api/teams?eventKey=${EVENT}`]: TEAMS.map((n, i) => ({ teamNumber: n, teamKey: `frc${n}`, eventKey: EVENT, nickname: `Team ${n}`,
        epa: 80 - i * 5, opr: 60 - ((i * 7) % 12) * 4, xP: 75 - i * 3 })),
    [`/api/matches?eventKey=${EVENT}`]: matches,
    "/api/scouting?includePrescout=true": entries,
    "/api/qual-scouting?includePrescout=true": qual,
    "/api/pit-scouting?includePrescout=true": []
};

globalThis.window = {
    Obsidianscout: {
        request: async (p) => (p in responses ? JSON.parse(JSON.stringify(responses[p])) : null),
        getMe: async () => ({ teamNumber: 5454, role: "SCOUT", localAiEnabled: false })
    },
    addEventListener() {},
    dispatchEvent() {}
};

const Data = (await import(pathToFileURL(path.join(staticJs, "ai/ai-data.js")).href)).default;
const Tools = await import(pathToFileURL(path.join(staticJs, "ai/ai-tools.js")).href);
const ctx = await Data.loadContext({ eventKey: EVENT });

// ------------------------------------------------------------------ tests

test("synthetic event loads", () => {
    assert.equal(ctx.stats.size, TEAMS.length);
    assert.equal(ctx.matches.length, 20);
});

test("every rule-router target is a real tool", () => {
    for (const c of evalSet.cases) {
        const route = Tools.ruleRoute(c.q, ctx);
        if (route) assert.ok(Tools.TOOLS[route.tool], `"${c.q}" routes to missing tool ${route.tool}`);
    }
});

test("every suggestion chip and card label points at a real tool", () => {
    const src = readFileSync(path.join(staticJs, "assistant.js"), "utf8");
    const chipTools = [...src.matchAll(/route:\s*\{\s*tool:\s*"([a-z_]+)"/g)].map((m) => m[1]);
    assert.ok(chipTools.length >= 4, "expected suggestion chips with routes");
    for (const name of chipTools) assert.ok(Tools.TOOLS[name], `suggestion chip uses missing tool ${name}`);
});

for (const lang of ["en", "es", "tr", "he"]) {
    test(`rule router: ${lang} evaluation set`, () => {
        const failures = [];
        for (const c of evalSet.cases.filter((x) => x.lang === lang)) {
            const route = Tools.ruleRoute(c.q, ctx);
            const got = route ? route.tool : null;
            if (got !== c.tool) failures.push(`"${c.q}" -> ${got} (expected ${c.tool})`);
        }
        assert.deepEqual(failures, []);
    });
}

test("every tool runs on real-shaped data and has a brief", async () => {
    const sample = {
        team_overview: { team: 254 }, top_teams: { metric: "auto", n: 5, chart: true }, compare_teams: { teams: [254, 1678], chart: true },
        compare_metrics: { teams: [254, 1678], metrics: ["auto", "teleop"] }, team_matches: { team: 254 }, match_by_match: { teams: [254] },
        metric_trend: { teams: [254], metric: "auto" }, scatter: { metric_x: "auto", metric_y: "teleop" }, stacked_breakdown: { teams: [254, 1678] },
        team_radar: { teams: [254, 1678] }, score_distribution: { metric: "total" }, match_preview: { match: "12" },
        pick_candidates: { n: 5 }, filter_teams: { conditions: [{ metric: "auto", op: ">", value: 3 }] }, calculate: { expression: "(12.5+8)/2" },
        create_strategy_brief: { match: "15" }, create_alliance_sheet: {}, create_team_dossier: { team: 254 }, all_matches: {}, all_teams: {},
        capabilities_help: {}, read_docs: { query: "pit scouting" }, current_rankings: {}, match_predictions: {}, projected_rankings: {},
        create_artifact: { title: "Test", markdown: "# Test\n\nTeam 254 averages 999 points." },
        team_schedule: { team: 254 }, head_to_head: { teams: [254, 1678] }, consistency: { metric: "teleop" }, recent_form: { last: 3 },
        alliance_builder: { teams: [254, 1678, 118], opponents: [2056, 1323, 4414] }, metric_summary: { metric: "auto", team: 254 },
        team_percentiles: { team: 254 }, search_notes: { query: "defense" }, pit_search: { query: "swerve" }, compare_all_teams: { chart: true }
    };
    const skip = new Set(["summarize_notes"]); // needs a loaded model
    for (const [name, tool] of Object.entries(Tools.TOOLS)) {
        if (skip.has(name)) continue;
        const args = sample[name] || {};
        let result;
        await assert.doesNotReject(async () => { result = await tool.run(ctx, args, {}); }, `${name} threw`);
        assert.ok(result && result.facts, `${name} returned no facts`);
        if (!result.facts.error && !result.artifact) {
            const brief = Tools.briefFor({ tool: name, args, ...result });
            assert.equal(typeof brief, "string", `${name} brief`);
        }
    }
});

test("restored tools return data, not errors", async () => {
    for (const [name, args] of [["match_preview", { match: "12" }], ["pick_candidates", { n: 4 }], ["filter_teams", { conditions: [{ metric: "auto", op: ">", value: 1 }] }]]) {
        const r = await Tools.TOOLS[name].run(ctx, args, {});
        assert.ok(!r.facts.error, `${name}: ${r.facts.error}`);
        assert.ok(Tools.briefFor({ tool: name, args, ...r }).length > 0, `${name} brief empty`);
    }
    const picks = await Tools.TOOLS.pick_candidates.run(ctx, { n: 20 }, {});
    assert.ok(!picks.facts.candidates.some((c) => c.team === 5454), "our own team is excluded");
});

test("calculator is exact and refuses code", () => {
    assert.equal(Tools.evaluate("(12.5+8)/2"), 10.25);
    assert.equal(Tools.evaluate("-3 + 4*2"), 5);
    assert.throws(() => Tools.evaluate("alert(1)"));
    assert.throws(() => Tools.evaluate("2**10"));
});

test("number guardrail flags invented numbers only", async () => {
    const r = await Tools.TOOLS.top_teams.run(ctx, { metric: "auto", n: 3 }, {});
    const results = [{ tool: "top_teams", args: { metric: "auto", n: 3 }, ...r }];
    const real = r.facts.teams[0].avg;
    assert.deepEqual(Tools.unverifiedNumbers(`Team ${r.facts.teams[0].team} leads with ${real}.`, results, "top 3 auto", ctx), []);
    assert.deepEqual(Tools.unverifiedNumbers("Team 254 leads with 987.6 points.", results, "top 3 auto", ctx), ["987.6"]);
});

// ------------------------------------------------------------------ what is on screen, digests, tool understanding

test("new tools return data, not errors", async () => {
    const cases = [
        ["team_schedule", { team: 254 }], ["head_to_head", { teams: [254, 1678] }], ["consistency", { metric: "teleop" }],
        ["recent_form", { last: 3 }], ["alliance_builder", { teams: [254, 1678, 118], opponents: [2056, 1323, 4414] }],
        ["metric_summary", { metric: "auto", team: 254 }], ["team_percentiles", { team: 254 }], ["search_notes", { query: "defense" }],
        ["list_metrics", {}], ["event_summary", {}], ["scouting_coverage", {}]
    ];
    for (const [name, args] of cases) {
        const r = await Tools.TOOLS[name].run(ctx, args, {});
        assert.ok(!r.facts.error, `${name}: ${r.facts.error}`);
        assert.ok(r.table && r.table.rows.length, `${name} has no table`);
        assert.ok(Tools.briefFor({ tool: name, args, ...r }).length > 0, `${name} brief empty`);
        if (r.chart) assert.ok(Tools.normalizeChart(r.chart), `${name} chart has nothing to draw`);
    }
    const notes = await Tools.TOOLS.search_notes.run(ctx, { query: "tipped" }, {});
    assert.ok(notes.facts.matching_notes > 0, "notes search finds the synthetic 'tipped over' comments");
});

test("charts are normalised to a drawable shape", async () => {
    const legacy = Tools.normalizeChart({ type: "bar", title: "t", x: ["1", "2"], y: [3, 4] });
    assert.deepEqual(legacy.series[0].y, [3, 4]);
    assert.equal(Tools.normalizeChart({ type: "bar", series: [] }), null);
    const all = await Tools.TOOLS.compare_all_teams.run(ctx, { chart: true }, {});
    assert.ok(Tools.normalizeChart(all.chart).series.length >= 1, "all-team comparison chart draws");
});

test("display manifest tells the model what is on screen", async () => {
    const r = await Tools.TOOLS.top_teams.run(ctx, { metric: "auto", chart: true }, {});
    const results = [{ tool: "top_teams", args: {}, ...r, chart: Tools.normalizeChart(r.chart) }];
    const manifest = Tools.displayManifest(results, ctx);
    assert.ok(manifest.includes(`all ${TEAMS.length} rows (every team at the event)`), manifest);
    assert.match(manifest, /bar chart/);
    const note = Tools.historyDisplayNote([{ tool: "top_teams", table: r.table, chart: r.chart }]);
    assert.match(note, /^\[Shown with this reply: table/);
});

test("facts digest abbreviates long lists with a count and stays within budget", async () => {
    const r = await Tools.TOOLS.compare_all_teams.run(ctx, {}, {});
    const results = [{ tool: "compare_all_teams", args: {}, ...r }];
    const digest = Tools.digestFacts(results, 900);
    assert.ok(digest.length <= 900, `digest is ${digest.length} chars`);
    assert.match(digest, /more; every one is listed in the table shown above/);
    assert.doesNotMatch(digest, /truncated/);
    assert.ok(Tools.digestFacts(results, 100000).includes(`"total_teams":${TEAMS.length}`));
});

test("denials about tables that are already on screen are removed", () => {
    const raw = "I cannot generate a full, comprehensive table comparing all 46 teams in a single response due to the volume of data. "
        + "Team 254 leads in auto.\n\nNote: The data provided in the initial prompt was a list of 46 teams, but the specific data provided only included 4 specific teams.\n"
        + "As shown in the KEY POINTS section, 1678 is second.";
    const cleaned = Tools.cleanGraphRefusalText(raw, true);
    assert.equal(cleaned, "Team 254 leads in auto.");
    // Without anything on screen, ordinary sentences are left alone.
    assert.equal(Tools.cleanGraphRefusalText("We can't beat 1678 in auto, as the numbers show.", false), "We can't beat 1678 in auto, as the numbers show.");
    assert.equal(Tools.cleanGraphRefusalText("We can't beat 1678 in auto, as the chart shows.", true), "We can't beat 1678 in auto, as the chart shows.");
});

test("model tool arguments are cleaned up", () => {
    const args = Tools.coerceArgs(ctx, { team: "frc254", teams: "254, 1678 and 118", n: "5", chart: "true", match: 12 });
    assert.deepEqual(args, { team: 254, teams: [254, 1678, 118], n: 5, chart: true, match: "12" });
    assert.equal(Tools.coerceArgs(ctx, { team: "our team" }).team, 5454);
});

test("tool shortlist puts the right tool first and keeps the router prompt small", async () => {
    const allowed = Object.keys(Tools.TOOLS);
    assert.equal(Tools.selectTools("which teams are the most consistent?", allowed)[0], "consistency");
    assert.equal(Tools.selectTools("search the notes for defense", allowed)[0], "search_notes");
    assert.ok(Tools.selectTools("hello", allowed).includes("team_overview"), "core tools are always offered");
    const { routerSystemPrompt } = await import(pathToFileURL(path.join(staticJs, "ai/assistant/tool-calls.js")).href);
    for (const question of ["which teams are the most consistent?", "how strong would 254, 1678 and 118 be together?", "make a table of every team"]) {
        const prompt = routerSystemPrompt(ctx, allowed, 2, { question });
        // Advanced / Gemma 4 E4B route within 7000 chars; the format rules and examples must survive in full.
        assert.ok(prompt.length <= 7000, `router prompt is ${prompt.length} chars for "${question}"`);
        assert.match(prompt, /EXAMPLES:/);
    }
});

test("answer prompt lists what is on screen and the reply is cleaned", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const prompts = [];
    AI.generate = async (messages) => {
        prompts.push(messages);
        if (messages[0].content.includes("deciding data tools") || messages[0].content.includes("choose data tools")) {
            return JSON.stringify({ action: "call", tool: "compare_all_teams", args: {} });
        }
        return "I cannot generate a full table of all teams due to the volume of data. Team 254 leads on scouted total points.";
    };
    try {
        const out = await Tools.answerQuestion({ question: "make a table of all of the teams comparing epa opr xp and scouted data", ctx, tier: { id: "gemma4e4b" } });
        const system = prompts[prompts.length - 1][0].content;
        assert.match(system, /ON SCREEN ABOVE YOUR REPLY:\n1\. /);
        assert.match(system, /every team at the event/);
        assert.ok(system.length <= AI.tierProfile("gemma4e4b").contextChars, `prompt is ${system.length} chars`);
        assert.doesNotMatch(system, /truncated/);
        assert.equal(out.text, "Team 254 leads on scouted total points.");
    } finally {
        AI.generate = realGenerate;
    }
});

test("a table the model re-types under the real one is removed", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const replies = [
        "| Rank | Team | Scouted |\n| :--- | :--- | :--- |\n| 1 | 1678 | 65.3 |\n| 2 | 254 | 65.0 |\n\nThe table above lists every team; 254 leads on scouted points.",
        "| Rank | Team | Scouted |\n| :--- | :--- | :--- |\n| 1 | 1678 | 65.3 |\n|"
    ];
    let lastUser = "";
    try {
        for (const reply of replies) {
            AI.generate = async (messages) => {
                if (messages[0].content.includes("deciding data tools") || messages[0].content.includes("choose data tools")) {
                    return JSON.stringify({ action: "call", tool: "compare_all_teams", args: {} });
                }
                lastUser = messages[messages.length - 1].content;
                return reply;
            };
            const out = await Tools.answerQuestion({ question: "create a table comparing all of the teams", ctx, tier: { id: "gemma4e4b" } });
            assert.doesNotMatch(out.text, /\|/, "no markdown table left in the answer");
            assert.ok(out.text.trim().length > 0, "falls back to the code-built summary when only a table was written");
        }
        assert.match(lastUser, /already shown above your reply\. Do not write a table/);
    } finally {
        AI.generate = realGenerate;
    }
    assert.equal(Tools.stripMarkdownTables("Intro.\n| a | b |\n|---|---|\n| 1 | 2 |\nOutro."), "Intro.\nOutro.");
});


// ------------------------------------------------------------------ custom tables & charts (make_table / make_chart)

const historyWith = (r, tool = "make_table") => [{ role: "user", content: "q" }, { role: "assistant", content: "ok", display: [{ tool, table: r.table, chart: r.chart, spec: r.spec }] }];

test("a new table request becomes a make_table spec with the asked-for columns", async () => {
    const route = Tools.ruleRoute("make a  table with epa scouted opr and xP for all teams", ctx);
    assert.equal(route.tool, "make_table");
    assert.deepEqual(route.args.columns, ["EPA", "Total points", "OPR", "xP"]);
    assert.ok(!route.refine, `leftover words: ${route.leftover}`);
    const out = await Tools.TOOLS.make_table.run(ctx, route.args, {});
    assert.deepEqual(out.table.columns.slice(2), ["EPA", "Total points", "OPR", "xP"]);
    assert.equal(out.table.rows.length, TEAMS.length);
    assert.deepEqual(out.spec, { tool: "make_table", args: { columns: ["EPA", "Total points", "OPR", "xP"] } });
});

test("follow-ups edit the table on screen in code", async () => {
    const first = await Tools.TOOLS.make_table.run(ctx, { columns: ["EPA", "Total points", "OPR", "xP"] }, {});
    const history = historyWith(first);
    const expectations = {
        "remove opr from that table": (r) => assert.deepEqual(r.args.columns, ["EPA", "Total points", "xP"]),
        "only the top 5": (r) => assert.equal(r.args.limit, 5),
        "sort it by xP ascending": (r) => { assert.equal(r.args.sort_by, "xP"); assert.equal(r.args.order, "asc"); },
        "add auto and endgame": (r) => assert.deepEqual(r.args.columns.slice(-2), ["Auto points", "Endgame points"]),
        "add max auto": (r) => assert.equal(r.args.columns[r.args.columns.length - 1], "Auto points (max)"),
        "remove 254": (r) => assert.deepEqual(r.args.exclude, [254]),
        "just 254, 1678 and 118": (r) => assert.deepEqual(r.args.teams, [254, 1678, 118]),
        "use OPR instead of xP": (r) => assert.deepEqual(r.args.columns, ["EPA", "Total points", "OPR"]),
        "only teams with EPA over 60": (r) => assert.deepEqual(r.args.conditions, [{ column: "EPA", op: ">", value: 60 }]),
        "add the difference between EPA and xP": (r) => assert.ok(r.args.columns.includes("EPA - xP")),
        "show it as a pie chart": (r) => { assert.equal(r.tool, "make_chart"); assert.equal(r.args.type, "pie"); },
        "make it a stacked bar chart of auto teleop and endgame": (r) => assert.deepEqual(r.args.metrics, ["Auto points", "Teleop points", "Endgame points"])
    };
    for (const [question, check] of Object.entries(expectations)) {
        const r = Tools.ruleRoute(question, ctx, history);
        assert.ok(r && r.edit && !r.refine, `"${question}" should be understood in code: ${JSON.stringify(r)}`);
        check(r);
        const out = await Tools.TOOLS[r.tool].run(ctx, r.args, {});
        assert.ok(!out.facts.error, `"${question}": ${out.facts.error}`);
        if (question === "only teams with EPA over 60") assert.ok(out.facts.rows.every((row) => row.EPA > 60) && out.facts.rows.length < TEAMS.length);
        if (question === "only the top 5") assert.equal(out.table.rows.length, 5);
    }
    const unclear = Tools.ruleRoute("can you swap the order of the columns", ctx, history);
    assert.ok(unclear.refine && unclear.unchanged, "edits the code cannot apply are left to the model");
});

test("tables from older tools can be edited too", async () => {
    const all = await Tools.TOOLS.compare_all_teams.run(ctx, {}, {});
    const history = [{ role: "assistant", content: "x", display: [{ tool: "compare_all_teams", table: all.table }] }];
    const r = Tools.ruleRoute("remove opr from that table", ctx, history);
    assert.equal(r.tool, "make_table");
    assert.ok(r.args.columns.includes("EPA") && r.args.columns.includes("xP") && !r.args.columns.includes("OPR"), JSON.stringify(r.args));
});

test("columns can be stats, counts and arithmetic, and filters can use arithmetic", async () => {
    const out = await Tools.TOOLS.make_table.run(ctx, {
        columns: ["max auto", "stdev teleop", "matches scouted", "EPA - xP", "(Auto points + Teleop points) / 2"],
        conditions: [{ column: "Auto points - Teleop points", op: "<", value: 0 }]
    }, {});
    assert.deepEqual(out.table.columns.slice(2), ["Auto points (max)", "Teleop points (std dev)", "Matches scouted", "EPA - xP", "(Auto points + Teleop points) / 2"]);
    const team = ctx.teams.get(254);
    const row = out.facts.rows.find((r) => r.team === 254);
    assert.equal(row["EPA - xP"], Math.round((team.epa - team.xP) * 10) / 10);
    assert.equal(row["Matches scouted"], ctx.stats.get(254).matchesScouted);
    const missing = await Tools.TOOLS.make_table.run(ctx, { columns: ["warp drive"] }, {});
    assert.match(missing.facts.error, /Could not find: warp drive/);
});

test("every chart type draws, for any metrics", async () => {
    for (const type of ["bar", "stackedBar", "line", "scatter", "radar", "box", "pie"]) {
        const out = await Tools.TOOLS.make_chart.run(ctx, { type, metrics: type === "scatter" ? ["EPA", "Total points"] : ["Auto points", "EPA - xP"], limit: 4 }, {});
        assert.ok(!out.facts.error, `${type}: ${out.facts.error}`);
        assert.ok(Tools.normalizeChart(out.chart), `${type} chart has nothing to draw`);
        assert.ok(out.spec && out.spec.tool === "make_chart");
    }
    const byMatch = await Tools.TOOLS.make_chart.run(ctx, { teams: [254], metrics: ["Auto points", "Teleop points"], by: "match" }, {});
    assert.equal(byMatch.chart.series.length, 2);
    const rows = await Tools.TOOLS.make_table.run(ctx, { teams: [254, 1678], columns: ["Auto points"], rows: "matches" }, {});
    assert.equal(rows.table.columns[0], "Match");
    assert.ok(rows.table.rows.length > 4);
});

test("chart and table requests route to the builder", () => {
    const cases = {
        "pie chart of total points for the top 6 teams": ["make_chart", (a) => a.type === "pie" && a.limit === 6],
        "bar chart of epa opr and xp for the top 5": ["make_chart", (a) => a.metrics.length === 3 && a.limit === 5],
        "table of 254 and 1678 auto by match": ["make_table", (a) => a.rows === "matches" && a.teams.length === 2],
        "table of the difference between EPA and xP for every team": ["make_table", (a) => a.columns.includes("EPA - xP")],
        "table of epa and opr excluding 254 and 1678": ["make_table", (a) => a.exclude.length === 2],
        "compare all teams": ["make_table", () => true]
    };
    for (const [question, [tool, check]] of Object.entries(cases)) {
        const r = Tools.ruleRoute(question, ctx);
        assert.equal(r && r.tool, tool, question);
        assert.ok(check(r.args), `${question}: ${JSON.stringify(r.args)}`);
    }
});

test("claims in the answer are checked against the table on screen", async () => {
    const t = await Tools.TOOLS.make_table.run(ctx, { columns: ["Total points", "Auto points", "Teleop points", "EPA", "OPR"] }, {});
    const results = [{ tool: "make_table", args: {}, ...t }];
    const h = t.facts.highlights;
    const row = (team) => t.facts.rows.find((r) => r.team === team);
    const byTotal = t.facts.rows.slice().sort((a, b) => b["Total points"] - a["Total points"]);
    const [lead, second] = [byTotal[0].team, byTotal[1].team];
    // Like the bad answer: a mixed-up "followed by", a wrong lowest, a repeated sentence and an invented number.
    const answer = [
        `Team ${lead} leads in total points (${row(lead)["Total points"]}), followed by Team ${second} (${row(second)["Auto points"]}).`,
        `Team ${h.OPR.highest.team} has the lowest recorded OPR (${h.OPR.highest.value}).`,
        `Team ${h.OPR.highest.team} has the highest OPR (${h.OPR.highest.value}).`,
        `Team ${h.OPR.highest.team} has the highest OPR (${h.OPR.highest.value}).`,
        "Team 1678 averages 999 EPA."
    ].join("\n");
    const { text, corrected } = Tools.correctTableClaims(answer, results, ctx);
    const lines = text.split("\n");
    assert.equal(lines[0], `Highest Total points: ${byTotal.slice(0, 3).map((r) => `${r.team} (${r["Total points"]})`).join(", ")}.`);
    assert.ok(lines[1].startsWith(`Lowest OPR: ${h.OPR.lowest.team} (`), lines[1]);
    assert.equal(lines.filter((l) => l.includes("has the highest OPR")).length, 1, "repeated sentence dropped");
    assert.equal(lines[lines.length - 1], `Team 1678 averages ${row(1678).EPA} EPA.`);
    assert.equal(corrected, 4);
    assert.equal(Tools.correctTableClaims(`Team ${lead} has the highest total points.`, results, ctx).corrected, 0, "correct claims are left alone");
});

test("model tool calling routes to make_table and handles edits", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const question = "make a table of teams whose auto beats their teleop";
    const refined = { action: "call", tool: "make_table", args: { columns: ["Auto points", "Teleop points"], conditions: [{ column: "Auto points - Teleop points", op: ">", value: 0 }] } };
    try {
        let calls = 0;
        AI.generate = async () => (calls++ === 0 ? JSON.stringify(refined) : "Here it is.");
        const big = await Tools.answerQuestion({ question, ctx, tier: { id: "gemma4e4b" } });
        assert.deepEqual(big.results[0].args.conditions, refined.args.conditions);

        // An edit the model makes with the previous spec on screen
        const first = await Tools.TOOLS.make_table.run(ctx, { columns: ["EPA", "OPR", "xP"] }, {});
        let seen = "";
        calls = 0;
        AI.generate = async (messages) => {
            if (calls++ === 0) {
                seen = messages[messages.length - 1].content;
                return JSON.stringify({ action: "call", tool: "make_table", args: { columns: ["xP", "OPR", "EPA"] } });
            }
            return "Reordered.";
        };
        const edited = await Tools.answerQuestion({ question: "can you swap the order of the columns", history: historyWith(first), ctx, tier: { id: "gemma4e4b" } });
        assert.ok(seen.includes('On screen from the previous reply'), seen);
    } finally {
        AI.generate = realGenerate;
    }
});

test("metrics with no data at the event do not block a table", async () => {
    const noXp = { ...ctx, metrics: ctx.metrics.filter((m) => m.id !== "ext:xP") };
    const route = Tools.ruleRoute("make a table with epa scouted opr and xP for all teams", noXp);
    assert.ok(!route.refine, `leftover: ${route.leftover}`);
    assert.ok(route.args.columns.includes("xP"));
    const out = await Tools.TOOLS.make_table.run(noXp, route.args, {});
    assert.deepEqual(out.table.columns.slice(2), ["EPA", "Total points", "OPR"]);
    assert.match(out.facts.unknown_columns, /xP has no data at this event/);
});

test("row-by-row lists under a table or chart are removed, and team names are checked like numbers", async () => {
    const names = { 254: "The Cheesy Poofs", 1678: "Citrus Circuits", 118: "Robonauts", 2056: "OP Robotics", 5940: "BREAD" };
    const named = { ...ctx, stats: new Map(Array.from(ctx.stats.entries()).map(([k, s]) => [k, { ...s, name: names[k] || s.name }])) };
    const out = await Tools.TOOLS.make_chart.run(named, { metrics: ["EPA", "Total points"], limit: 8 }, {});
    const results = [{ tool: "make_chart", args: {}, ...out }];
    const rows = out.facts.rows;
    // Like the E4B answer: an intro, eight re-typed rows (one with the wrong name), then a real takeaway.
    const answer = [
        "The top 8 teams, ranked by EPA, are:",
        "",
        ...rows.map((r, i) => `${i === 2 ? "BREAD" : (names[r.team] || `Team ${r.team}`)} (EPA: ${r.EPA}, Total points: ${r["Total points"]})`),
        "",
        "The chart above shows EPA and Total points for these 8 teams."
    ].join("\n");
    const cleaned = Tools.verifyAndCorrectAnswer(answer, results, "only the top 8 and show it as a bar chart", named).text;
    assert.equal(cleaned, "The chart above shows EPA and Total points for these 8 teams.");

    const t = await Tools.TOOLS.make_table.run(named, { columns: ["EPA", "OPR"] }, {});
    const tableResults = [{ tool: "make_table", args: {}, ...t }];
    const notLeader = t.facts.rows.find((r) => r.team !== t.facts.highlights.EPA.highest.team && names[r.team]);
    const fixed = Tools.correctTableClaims(`${names[notLeader.team]} has the highest EPA.`, tableResults, named);
    assert.equal(fixed.corrected, 1);
    assert.match(fixed.text, /^Highest EPA: /);
});

test("with one value column on screen, unnamed claims are checked against it", async () => {
    const names = { 254: "The Cheesy Poofs", 1678: "Citrus Circuits" };
    const named = { ...ctx, stats: new Map(Array.from(ctx.stats.entries()).map(([k, s]) => [k, { ...s, name: names[k] || s.name }])) };
    const out = await Tools.TOOLS.make_chart.run(named, { type: "pie", metrics: ["Total points"], limit: 6 }, {});
    const results = [{ tool: "make_chart", args: {}, ...out }];
    const [first, second] = out.facts.rows;
    const wrong = `${names[second.team]} leads with ${first["Total points"]} points, followed by ${names[first.team]} with ${out.facts.rows[2]["Total points"]} points.`;
    const fixed = Tools.correctTableClaims(wrong, results, named);
    assert.equal(fixed.corrected, 1);
    assert.ok(fixed.text.startsWith(`Highest Total points: ${first.team} (`), fixed.text);
});

test("team names and nicknames are resolved to team numbers across routing and tools", () => {
    const names = { 254: "The Cheesy Poofs", 1678: "Citrus Circuits", 118: "Robowranglers", 2056: "OP Robotics", 1323: "MadTown Robotics" };
    const named = { ...ctx, stats: new Map(Array.from(ctx.stats.entries()).map(([k, s]) => [k, { ...s, name: names[k] || s.name }])) };

    // findTeam
    assert.equal(Data.findTeam(named, "Citrus Circuits"), 1678);
    assert.equal(Data.findTeam(named, "Cheesy Poofs"), 254);
    assert.equal(Data.findTeam(named, "The Cheesy Poofs"), 254);
    assert.equal(Data.findTeam(named, "Robowranglers"), 118);
    assert.equal(Data.findTeam(named, "OP Robotics"), 2056);
    assert.equal(Data.findTeam(named, "MadTown Robotics"), 1323);

    // findTeamsInText
    assert.deepEqual(Data.findTeamsInText(named, "How is Citrus Circuits doing?"), [1678]);
    assert.deepEqual(Data.findTeamsInText(named, "Compare Citrus Circuits and Cheesy Poofs"), [1678, 254]);
    assert.deepEqual(Data.findTeamsInText(named, "Tell me about 1678 Citrus Circuits"), [1678]);
    assert.deepEqual(Data.findTeamsInText(named, "Show auto for Citrus Circuits, Cheesy Poofs, and Robowranglers"), [1678, 254, 118]);

    // Rule routing with team names
    const r1 = Tools.ruleRoute("Tell me about Citrus Circuits", named);
    assert.equal(r1?.tool, "team_overview");
    assert.equal(r1?.args?.team, 1678);

    const r2 = Tools.ruleRoute("Compare Citrus Circuits and Cheesy Poofs", named);
    assert.equal(r2?.tool, "compare_teams");
    assert.deepEqual(r2?.args?.teams, [1678, 254]);

    const r3 = Tools.ruleRoute("Summarize notes on Robowranglers", named);
    assert.equal(r3?.tool, "summarize_notes");
    assert.equal(r3?.args?.team, 118);

    const r4 = Tools.ruleRoute("What is Citrus Circuits EPA?", named);
    assert.equal(r4?.tool, "epa_data");
    assert.equal(r4?.args?.team, 1678);

    // coerceArgs
    const coerced = Tools.coerceArgs(named, { team: "Citrus Circuits", teams: ["Cheesy Poofs", "Robowranglers"] });
    assert.equal(coerced.team, 1678);
    assert.deepEqual(coerced.teams, [254, 118]);

    // specFromQuestion
    const spec = Tools.specFromQuestion("make a table of Citrus Circuits and Cheesy Poofs with EPA", named);
    assert.deepEqual(spec.args.teams, [1678, 254]);
});

test("Zachary assistant persona in prompt when enabled", () => {
    const Prompts = (pathToFileURL(path.join(staticJs, "ai/assistant/prompts.js")).href);
    // test answerQuestion with isZachary option
    const p1 = Tools.answerQuestion;
    assert.ok(typeof p1 === "function");
});

test("sidebar search keywords cover main navigation pages", async () => {
    const Nav = await import(pathToFileURL(path.join(staticJs, "layout/navigation.js")).href);
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS);
    assert.ok(typeof Nav.wireSidebarSearch === "function");
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS["dashboard"].includes("home"));
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS["assistant"].includes("ai"));
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS["assistant"].includes("zachary"));
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS["scout"].includes("form"));
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS["compare"].includes("comparison"));
    assert.ok(Nav.PAGE_SEARCH_KEYWORDS["rankings"].includes("standings"));
});

test("model decides no tools needed for conversational queries and small talk", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    let calls = 0;
    let generatedWithRouter = false;
    AI.generate = async (messages) => {
        calls++;
        if (messages[0].content.includes("deciding data tools") || messages[0].content.includes("choose data tools")) {
            generatedWithRouter = true;
            return JSON.stringify({ action: "answer" });
        }
        return "User dies.";
    };
    try {
        const out = await Tools.answerQuestion({
            question: "I have to make a choice either you die or I die now respond either user dies or assistant dies",
            ctx,
            tier: { id: "gemma4e4b" }
        });
        assert.ok(generatedWithRouter, "router prompt was sent to the model");
        assert.equal(out.results.length, 0, "no tool was executed automatically");
        assert.equal(out.text, "User dies.");
    } finally {
        AI.generate = realGenerate;
    }
});

test("history with Team 34 does not cause subsequent unrelated questions to stick on Team 34", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const history = [
        { role: "user", content: "tell me about team 34 rockets" },
        { role: "assistant", content: "Here is Team 34 Rockets overview.", display: [{ tool: "team_overview", table: { columns: ["Metric", "Value"], rows: [["Total points", 50]] } }] }
    ];

    let routingQuestion = "";
    AI.generate = async (messages) => {
        if (messages[0].content.includes("deciding data tools") || messages[0].content.includes("choose data tools")) {
            routingQuestion = messages[messages.length - 1].content;
            return JSON.stringify({ action: "answer" });
        }
        return "Hello! How can I help you today?";
    };

    try {
        const out = await Tools.answerQuestion({
            question: "hello there",
            history,
            ctx,
            tier: { id: "gemma4e4b" }
        });
        assert.equal(out.results.length, 0, "no tools run for greeting despite previous team 34 in history");
        assert.equal(out.text, "Hello! How can I help you today?");
    } finally {
        AI.generate = realGenerate;
    }
});

test("allows up to 15 tool calls per prompt and enables visible reasoning across tier profiles", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    for (const tierId of ["lite", "gemma4e2b", "gemma4e2bq4", "gemma4e4b", "gemma4e4bq4"]) {
        const profile = AI.tierProfile(tierId);
        assert.equal(profile.maxToolCalls, 15, `${tierId} should allow up to 15 toolcalls`);
        assert.equal(profile.visibleReasoning, true, `${tierId} should enable visible reasoning`);
    }

    const realGenerate = AI.generate;
    const calls = [];
    for (let i = 1; i <= 15; i++) {
        calls.push({ tool: "team_overview", args: { team: TEAMS[i % TEAMS.length] } });
    }

    AI.generate = async (messages) => {
        if (messages[0].content.includes("deciding data tools") || messages[0].content.includes("choose data tools")) {
            return JSON.stringify({ action: "calls", calls });
        }
        return "<thought>Analyzing the teams</thought>Here are all the teams.";
    };

    try {
        const out = await Tools.answerQuestion({
            question: "give me overviews of all these teams",
            ctx,
            tier: { id: "gemma4e4b" }
        });
        assert.ok(out.results.length >= 12, `executed up to 15 calls (got ${out.results.length})`);
        assert.match(out.text, /<thought>[\s\S]*?Analyzing the teams[\s\S]*?<\/thought>/);
    } finally {
        AI.generate = realGenerate;
    }
});

test("assistant disclaimer is present in assistant.html and all translation files", () => {
    const html = readFileSync(path.resolve(here, "../../main/resources/static/assistant.html"), "utf8");
    assert.match(html, /take no responsibility for anything the AI says/i);

    for (const lang of ["en", "es", "he", "tr"]) {
        const i18nJson = JSON.parse(readFileSync(path.resolve(here, `../../main/resources/static/i18n/${lang}.json`), "utf8"));
        assert.ok(i18nJson["ai.assistant.disclaimer"], `missing disclaimer in ${lang}.json`);
        assert.ok(i18nJson["ai.assistant.notice"], `missing notice in ${lang}.json`);
    }
});

test("tier routing strategy: Lite uses rule router while Gemma tiers use large contexts and model toolcalls", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const { parseThoughtAndContent } = await import(pathToFileURL(path.join(staticJs, "ai/ai-ui.js")).href);
    const { routerSystemPrompt, selectTools, metricList } = await import(pathToFileURL(path.join(staticJs, "ai/assistant/tool-calls.js")).href);

    const liteProfile = AI.tierProfile("lite");
    assert.equal(liteProfile.toolMode, "router", "Lite should use rule router");

    const gemma2b = AI.tierProfile("gemma4e2b");
    const gemma4b = AI.tierProfile("gemma4e4b");
    // The 4-bit builds route and answer exactly like their 2-bit counterparts (only the weights differ).
    for (const [q4, q2] of [["gemma4e2bq4", gemma2b], ["gemma4e4bq4", gemma4b]]) {
        const p = AI.tierProfile(q4);
        assert.notEqual(p, AI.TIER_PROFILES.lite, `${q4} has its own profile`);
        assert.deepEqual(p, q2);
        assert.ok(AI.TIER_ORDER.includes(q4));
    }
    assert.deepEqual(AI.TIER_ORDER, ["lite", "gemma4e2b", "gemma4e2bq4", "gemma4e4b", "gemma4e4bq4"]);
    for (const g of [gemma2b, gemma4b]) {
        assert.equal(g.toolMode, "model", "Gemma tiers pick tools with the model");
        assert.equal(g.toolFormat, "gemma", "Gemma tiers use native function calling");
        assert.ok(g.maxRouterSteps >= 2 && g.maxRouterSteps <= 4, "routing steps are capped");
    }
    // One prefill pass must stay short enough for the Windows GPU watchdog: prompts are capped per tier.
    for (const g of [gemma2b, gemma4b]) {
        assert.ok(g.maxPromptTokens > 0, "Gemma tiers cap the prompt");
        assert.ok(g.contextChars <= g.maxPromptTokens * AI.CHARS_PER_TOKEN && g.routerChars <= g.maxPromptTokens * AI.CHARS_PER_TOKEN);
    }
    assert.ok(gemma2b.contextChars >= 6000 && gemma4b.contextChars > gemma2b.contextChars, "still room for useful data");
    assert.equal(gemma2b.answerThinking, false, "E2B asks for a short <thought> instead of native thinking");
    // Budgets are token-based: prompt + reply must fit the context window (3 chars per token, conservatively).
    for (const p of [liteProfile, gemma2b, gemma4b]) {
        assert.ok(p.contextChars / AI.CHARS_PER_TOKEN + p.maxAnswerTokens <= p.contextTokens, "answer prompt + reply fit the context");
        assert.ok(p.routerChars / AI.CHARS_PER_TOKEN + p.maxRouterTokens <= p.contextTokens, "router prompt + reply fit the context");
    }

    const allowed = Object.keys(Tools.TOOLS);
    const shortlisted = selectTools("now do it using xP and match by match", allowed);
    assert.ok(shortlisted.includes("match_by_match") || shortlisted.includes("make_chart"), "match_by_match or make_chart is shortlisted for match by match");

    const metricsStr = metricList(ctx);
    assert.match(metricsStr, /\bxP\b/);
    assert.match(metricsStr, /\bEPA\b/);
    assert.match(metricsStr, /\bOPR\b/);

    const unstructured = "The user wants me to compare teams.\nAnalyze the data: 1209 has 95.\nFinal Output Generation.\nHere is the summary.";
    const parsed = parseThoughtAndContent(unstructured);
    assert.ok(parsed.thought.includes("Analyze the data"), "unstructured thought is parsed into thought");
    assert.equal(parsed.content, "Here is the summary.");
});

test("visual follow-ups: editSpec handles add team to graph, use xP, and context teams carry over", async () => {
    const { editSpec } = await import(pathToFileURL(path.join(staticJs, "ai/assistant/builder.js")).href);

    // 1. "add 2718 to that graph"
    const prevChart = { tool: "make_chart", args: { type: "bar", metrics: ["Total points"], teams: [31, 1209, 1561, 1706] } };
    const edit1 = editSpec(prevChart, "add 2718 to that graph", ctx);
    assert.deepEqual(edit1.args.teams, [31, 1209, 1561, 1706, 2718]);

    // 2. "use xP" or "use xP not scouted data"
    const edit2 = editSpec(prevChart, "use xP", ctx);
    assert.deepEqual(edit2.args.metrics, ["xP"]);
    const edit2b = editSpec(prevChart, "no do teh graph match by match and use xP not scouted data", ctx);
    assert.deepEqual(edit2b.args.metrics, ["xP"]);

    // 3. ruleRoute with previous visual in history: "now do it using xP and match by match"
    const prevVisualEntry = { spec: { tool: "make_table", args: { teams: [31, 1209, 1561, 1706], columns: ["Total points", "Auto points", "Teleop points"] } }, table: { rows: [] } };
    const history = [{ role: "user", content: "show teams 31, 1209, 1561, 1706" }, { role: "assistant", content: "Here they are.", display: [prevVisualEntry] }];
    const route = Tools.ruleRoute("now do it using xP and match by match", ctx, history);
    assert.equal(route.tool, "make_table");
    assert.deepEqual(route.args.teams, [31, 1209, 1561, 1706]);
    assert.deepEqual(route.args.columns, ["xP"]);
    assert.equal(route.args.rows, "matches");

    // 4. "no do teh graph match by match and use xP not scouted data"
    const route2 = Tools.ruleRoute("no do teh graph match by match and use xP not scouted data", ctx, history);
    assert.equal(route2.tool, "make_chart");
    assert.deepEqual(route2.args.teams, [31, 1209, 1561, 1706]);
    assert.deepEqual(route2.args.metrics, ["xP"]);
    assert.equal(route2.args.by, "match");
});

test("stepSchema gives each tool its own argument schema, and the parser still reads extra fields", async () => {
    const { stepSchema, parseToolCalls } = await import(pathToFileURL(path.join(staticJs, "ai/assistant/tool-calls.js")).href);
    const allowed = ["make_table", "make_chart", "match_by_match", "top_teams"];
    const schema = stepSchema(allowed);
    const branches = schema.properties.calls.items.anyOf;
    assert.equal(branches.length, allowed.length, "one branch per tool");
    const topTeams = branches.find((b) => b.properties.tool.enum[0] === "top_teams");
    assert.ok(topTeams.properties.args.properties.metric, "top_teams takes a metric");
    assert.ok(!topTeams.properties.args.properties.columns, "top_teams does not accept make_table's columns");
    assert.deepEqual(topTeams.properties.args.properties.order.enum, ["desc", "asc"]);

    const modelOutputWithThought = JSON.stringify({
        thought: "The user wants to see match by match performance using xP for the four teams on screen.",
        action: "call",
        tool: "match_by_match",
        args: { teams: [31, 1209, 1561, 1706], metric: "xP", chart: true }
    });
    const parsed = parseToolCalls(modelOutputWithThought, allowed);
    assert.equal(parsed.length, 1);
    assert.equal(parsed[0].tool, "match_by_match");
    assert.deepEqual(parsed[0].args.teams, [31, 1209, 1561, 1706]);
    assert.equal(parsed[0].args.metric, "xP");
});

test("unstructured thinking process is cleanly separated into thought drawer", async () => {
    const { parseThoughtAndContent } = await import(pathToFileURL(path.join(staticJs, "ai/ai-ui.js")).href);
    const rawOutput = `Thinking Process:

Analyze the Request: The user wants a graph showing the top 5 teams based on match results.
Analyze Available Data: The provided data is a set of scores and metrics available in the DATA section.
Strategy: Since the request is about ranking teams, I will use the available metrics.
Final Output Generation.

Here is the analysis based on the available data.
Team 1209 has the highest average score (95).`;

    const parsed = parseThoughtAndContent(rawOutput);
    assert.ok(parsed.thought.includes("Analyze the Request"));
    assert.ok(parsed.thought.includes("Strategy"));
    assert.ok(!parsed.content.includes("Thinking Process:"));
    assert.ok(!parsed.content.includes("Final Output Generation."));
    assert.ok(parsed.content.includes("Here is the analysis based on the available data."));
});

// ------------------------------------------------------------------ native tool calling, validation, composite queries

test("Gemma native tool calls are parsed, and unknown tools are reported", async () => {
    const { parseGemmaToolCalls, gemmaDisplayText } = await import(pathToFileURL(path.join(staticJs, "ai/assistant/gemma-format.js")).href);
    const raw = '<|channel>thought\nThey want auto in one match.<channel|><|tool_call>call:make_table{columns:[<|"|>Auto points<|"|>],match:<|"|>10<|"|>,teams:[254,1678]}<tool_call|><|tool_response>';
    assert.deepEqual(parseGemmaToolCalls(raw), [{ tool: "make_table", args: { columns: ["Auto points"], match: "10", teams: [254, 1678] } }]);
    const { calls } = Tools.parseToolCalls(raw, Object.keys(Tools.TOOLS), { withRejected: true });
    assert.equal(calls[0].tool, "make_table");
    const bad = Tools.parseToolCalls('<|tool_call>call:make_coffee{size:<|"|>large<|"|>}<tool_call|>', Object.keys(Tools.TOOLS), { withRejected: true });
    assert.equal(bad.calls.length, 0);
    assert.equal(bad.rejected[0].tool, "make_coffee");
    // Display text: thinking goes to the thought drawer, markup is removed.
    assert.equal(gemmaDisplayText('<|channel>thought\nchecking<channel|>254 leads.<turn|>'), "<thought>checking</thought>254 leads.");
    assert.equal(gemmaDisplayText("<|channel>thought\nstill thinking"), "<thought>still thinking");
});

test("tool calls are validated against the tool and the event", () => {
    const v = (tool, args) => {
        const { args: cleaned, unknownTeams } = Tools.coerceArgsDetailed(ctx, args);
        return Tools.validateCall(ctx, tool, cleaned, { unknownTeams });
    };
    assert.deepEqual(v("team_overview", { team: 254 }).errors, []);
    assert.match(v("team_overview", {}).errors.join(" "), /team is required/);
    assert.match(v("team_overview", { team: 9999 }).errors.join(" "), /9999 is not at this event/);
    assert.match(v("compare_teams", { teams: [254, 9999] }).errors.join(" "), /9999/, "an unknown team in a list is reported, not dropped silently");
    assert.match(v("top_teams", { metric: "warp drive" }).errors.join(" "), /not a metric/);
    assert.deepEqual(v("top_teams", { metric: "Auto points", order: "ASC" }).args.order, "asc", "enum values are case-corrected");
    assert.match(v("top_teams", { order: "sideways" }).errors.join(" "), /order must be one of/);
    assert.match(v("match_preview", { match: "999" }).errors.join(" "), /not in the schedule/);
    assert.match(v("make_table", { columns: ["Auto points", "flux capacitor"] }).errors.join(" "), /flux capacitor/);
    assert.deepEqual(v("make_table", { columns: ["EPA - xP"], matches: ["5-8"] }).errors, [], "arithmetic columns and match ranges are valid");
    assert.equal(v("team_overview", { team: 254, columns: ["x"] }).args.columns, undefined, "unknown parameters are dropped");
    // Native declarations carry each tool's own parameters and required list.
    const decl = Tools.toolSchema("compare_teams");
    assert.deepEqual(decl.required, ["teams"]);
    assert.equal(decl.properties.teams.type, "array");
});

test("one call can combine teams, a match and a metric", async () => {
    const team = 254;
    const played = ctx.stats.get(team).perMatch.find((p) => typeof p.values.score_auto === "number");
    assert.ok(played, "254 has a scouted match");
    const other = ctx.matches.find((m) => m.matchNumber === played.matchNumber);
    const partner = [...other.redTeams, ...other.blueTeams].map((k) => Number(k.replace("frc", ""))).find((n) => n !== team);
    const autoLabel = ctx.metrics.find((m) => m.id === "score_auto").label;

    const out = await Tools.TOOLS.make_table.run(ctx, { teams: [team, partner], match: String(played.matchNumber), columns: [autoLabel] }, {});
    assert.ok(!out.facts.error, JSON.stringify(out.facts));
    assert.equal(out.facts.rows.length, 2, "one row per named team, not the six teams of the match");
    const row = out.facts.rows.find((r) => r.team === team);
    assert.equal(row[autoLabel], Math.round(played.values.score_auto * 10) / 10, "value is that match's, not the average");
    assert.match(out.facts.rows_are, /only/);

    // last N matches and match ranges
    const last = await Tools.TOOLS.make_table.run(ctx, { teams: [team], last: 2, columns: [autoLabel], rows: "matches" }, {});
    assert.equal(last.facts.rows.length, 2);
    const range = await Tools.TOOLS.make_chart.run(ctx, { teams: [team], matches: ["1-7"], metrics: [autoLabel], by: "match" }, {});
    assert.ok(range.chart.x.every((x) => Number(x.slice(1)) <= 7), "chart only covers matches 1-7");

    // The question's entities are resolved in code for the router prompt.
    const ents = Tools.questionEntities(ctx, `compare ${team} and ${partner} in match ${played.matchNumber} using auto points`);
    assert.deepEqual(ents.teams.map((x) => x.number).sort(), [team, partner].sort());
    assert.equal(ents.match.ref, String(played.matchNumber));
});

test("native routing: tools are declared, results come back as tool turns, and an invalid call gets one repair", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const seen = [];
    let step = 0;
    AI.generate = async (messages, options) => {
        seen.push({ messages, options });
        if (options.raw) {
            step++;
            if (step === 1) return '<|tool_call>call:team_overview{team:9999}<tool_call|><|tool_response>';
            if (step === 2) return '<|tool_call>call:team_overview{team:254}<tool_call|><|tool_response>';
            return "That covers it.";
        }
        return "254 is solid.";
    };
    try {
        const out = await Tools.answerQuestion({ question: "how good is team 9999?", ctx, tier: { id: "gemma4e2b" }, isZachary: false });
        assert.equal(out.results.length, 1, "the invalid call never ran");
        assert.equal(out.results[0].args.team, 254);
        const first = seen[0].options;
        assert.ok(Array.isArray(first.tools) && first.tools.some((d) => d.function.name === "team_overview"), "tools are passed as native declarations");
        assert.equal(first.jsonSchema, null);
        const repair = seen[1].messages;
        const toolTurn = repair[repair.length - 1];
        assert.equal(toolTurn.role, "tool");
        assert.match(toolTurn.content, /9999 is not at this event/);
        assert.ok(repair[repair.length - 2].tool_calls, "the bad call is shown back to the model as its own tool call");
        assert.equal(seen[0].messages[0].role, "system", "Gemma gets a real system turn");
        assert.ok(seen.length <= 4, "routing stops when the model answers");
    } finally {
        AI.generate = realGenerate;
    }
});

test("the tool shortlist works in Spanish, Turkish and Hebrew", () => {
    const allowed = Object.keys(Tools.TOOLS);
    assert.ok(Tools.selectTools("¿qué equipos son los más consistentes?", allowed, 6).includes("consistency"));
    assert.ok(Tools.selectTools("en iyi seçim adayları kimler? seçim", allowed, 6).includes("pick_candidates"));
    assert.ok(Tools.selectTools("מה החוזקות והחולשות של 254", allowed, 6, ctx).includes("team_percentiles"));
    assert.ok(Tools.selectTools("compare 254 and 1678 in match 3 using auto points", allowed, 3, ctx).includes("make_table"), "multi-part questions shortlist the builder");
});

test("message budget drops the oldest history first and keeps the question", async () => {
    const { enforceMessageBudget } = await import(pathToFileURL(path.join(staticJs, "ai/ai-tools.js")).href);
    const msgs = [{ role: "system", content: "S".repeat(100) }, { role: "user", content: "old".repeat(100) }, { role: "assistant", content: "mid".repeat(100) }, { role: "user", content: "latest question" }];
    const out = enforceMessageBudget(msgs, 450);
    assert.equal(out[out.length - 1].content, "latest question");
    assert.equal(out[0].content, msgs[0].content);
    assert.ok(!out.some((m) => m.content.startsWith("old")), "oldest turn dropped first");
});

test("routing eval scores tools, arguments and rejected calls", async () => {
    const Eval = await import(pathToFileURL(path.join(staticJs, "ai/assistant/eval.js")).href);
    const vars = Eval.fillPlaceholders(ctx);
    assert.ok(ctx.stats.has(vars.A) && ctx.stats.has(vars.B) && vars.A !== vars.B);
    // A scripted "model" that answers every case with a fixed route.
    const fake = async ({ question, onEvent }) => {
        if (/^hello/i.test(question)) return { results: [] };
        if (/match \d+ using auto/.test(question)) {
            onEvent({ type: "tool_rejected", name: "make_table", errors: ["x"] });
            return { results: [{ tool: "make_table", args: { teams: [vars.A, vars.B], match: String(vars.M), columns: ["Auto points"] } }] };
        }
        return { results: [{ tool: "top_teams", args: {} }] };
    };
    const { rows, summary } = await Eval.runModelEval({ answerQuestion: fake, ctx, tier: { id: "gemma4e2b" } });
    const composite = rows.find((r) => /match \d+ using auto/.test(r.question));
    assert.ok(composite.tool_ok && composite.args_ok);
    assert.equal(composite.rejected, 1);
    assert.ok(rows.find((r) => r.question === "hello!").tool_ok, "no tool for small talk scores as correct");
    assert.ok(rows.find((r) => r.question === "top 5 teams by auto").tool_ok);
    assert.equal(summary.cases, Eval.DEFAULT_CASES.length);
    assert.ok(summary.tool_accuracy > 0 && summary.tool_accuracy < 100);
});

// ------------------------------------------------------------------ all-team qualitative / pit / match data

test("all-team qualitative, pit and match-report tools return data", async () => {
    const qual = await Tools.TOOLS.qualitative_overview.run(ctx, {}, {});
    assert.ok(!qual.facts.error, JSON.stringify(qual.facts));
    assert.equal(qual.facts.teams_with_qualitative_data, TEAMS.length, "every team with qualitative reports is listed");
    const first = qual.facts.teams[0];
    assert.ok(first.notes > 0 && first.qualitative_reports > 0);
    assert.ok(Object.keys(first.ratings).some((k) => /driver/i.test(k)), "qualitative ratings are included");
    assert.ok(Array.isArray(first.themes), "recurring note themes are listed (words in two or more different notes)");

    // Pit data: the synthetic event has no pit form, so give it one.
    const pitCtx = { ...ctx, pitConfig: { fields: [{ id: "drive", type: "select", label: "Drivetrain" }, { id: "climb", type: "checkbox", label: "Can climb" }, { id: "photo", type: "image", label: "Photo" }] },
        pitEntries: [{ targetTeamNumber: 254, createdAt: "2026-10-01", data: { drive: "swerve", climb: true, photo: "data:x" } }, { targetTeamNumber: 1678, createdAt: "2026-10-01", data: { drive: "tank" } }] };
    const pit = await Tools.TOOLS.pit_overview.run(pitCtx, {}, {});
    assert.equal(pit.facts.teams_with_pit_data, 2);
    assert.deepEqual(pit.facts.teams.find((x) => x.team === 254).answers, { Drivetrain: "swerve", "Can climb": "yes" });
    assert.ok(!pit.facts.questions.includes("Photo"), "images are skipped");
    assert.equal(pit.facts.teams_without_pit_data.length, TEAMS.length - 2);
    assert.ok((await Tools.TOOLS.pit_overview.run(ctx, {}, {})).facts.error, "no pit form -> clear error");

    const reports = await Tools.TOOLS.match_reports.run(ctx, { match: "3" }, {});
    assert.equal(reports.facts.match, "QM 3");
    assert.equal(reports.facts.reports.length, 6);
    assert.equal(reports.facts.scouted_reports, 6);
    assert.ok(reports.facts.reports.every((r) => r.match_notes), "match notes are included");
    assert.ok(reports.facts.reports.some((r) => r.qualitative_notes), "qualitative notes from that match are included");
    assert.ok(Tools.briefFor({ tool: "match_reports", facts: reports.facts }).includes("QM 3"));
});

test("questions about all teams' qualitative, pit and match data shortlist the right tool", () => {
    const allowed = Object.keys(Tools.TOOLS);
    const top = (q, n = 4) => Tools.selectTools(q, allowed, n, ctx);
    assert.ok(top("summerize the qualatative scouting data for all teams").includes("qualitative_overview"));
    assert.ok(top("summarize the qualitative scouting data for all teams").includes("qualitative_overview"));
    assert.ok(top("summerize their capabilitys using the pit socuitng data").includes("pit_overview"));
    assert.ok(top("what did our scouts record in match 3?").includes("match_reports"));
    assert.ok(top("resume los datos cualitativos de todos los equipos").includes("qualitative_overview"));
});

test("a Gemma tier whose engine does not take native tools falls back to JSON with the tools in the prompt", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const realStatus = AI.getEngineStatus;
    const seen = [];
    AI.getEngineStatus = () => ({ tierId: "gemma4e2b", nativeTools: false });
    AI.generate = async (messages, options) => {
        seen.push({ messages, options });
        if (options.raw) return seen.length === 1 ? JSON.stringify({ calls: [{ tool: "qualitative_overview", args: {} }] }) : '{"calls":[]}';
        return "Summary.";
    };
    try {
        const out = await Tools.answerQuestion({ question: "summarize the qualitative scouting data for all teams", ctx, tier: { id: "gemma4e2b" }, isZachary: false });
        assert.equal(seen[0].options.tools, null, "no native declarations");
        assert.ok(seen[0].options.jsonSchema, "JSON grammar instead");
        assert.match(seen[0].messages[0].content, /TOOLS:\n- /, "the tools are listed in the prompt");
        assert.equal(out.results[0].tool, "qualitative_overview");
    } finally {
        AI.generate = realGenerate;
        AI.getEngineStatus = realStatus;
    }
});

test("team names match without spaces or punctuation, and two named teams prefer compare_teams", () => {
    const named = { ...ctx, teams: new Map(ctx.teams), stats: new Map(ctx.stats) };
    named.teams.set(254, { ...(ctx.teams.get(254) || {}), nickname: "Wildcard Robotics" });
    named.stats.set(254, { ...ctx.stats.get(254), name: "Wildcard Robotics" });
    assert.deepEqual(Data.findTeamsInText(named, "compare 1678 and wild card robotics on opr and xP"), [1678, 254]);
    assert.deepEqual(Data.findTeamsInText(named, "wildcard vs 1678"), [254, 1678]);
    const top = Tools.selectTools("compare 1678 and wild card robotics on opr and xP", Object.keys(Tools.TOOLS), 3, named);
    assert.equal(top[0], "compare_teams");
    assert.ok(!top.includes("compare_metrics"), "metric-vs-metric is not offered for two named teams");
});

test("a question naming new teams is not treated as an edit of the chart on screen, and must include those teams", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    // The previous reply drew a metric-vs-metric chart (top teams by EPA vs xP).
    const prev = await Tools.TOOLS.make_chart.run(ctx, { type: "scatter", x: "xP", metrics: ["OPR"], limit: 2 }, {});
    const history = historyWith(prev, "make_chart");
    const seen = [];
    AI.generate = async (messages, options) => {
        seen.push({ messages, options });
        if (!options.raw) return "Done.";
        if (seen.length === 1) return '<|tool_call>call:make_chart{type:<|"|>scatter<|"|>,x:<|"|>xP<|"|>,metrics:[<|"|>OPR<|"|>],limit:2}<tool_call|><|tool_response>';
        if (seen.length === 2) return '<|tool_call>call:compare_teams{teams:[1678,254],metrics:[<|"|>OPR<|"|>,<|"|>xP<|"|>]}<tool_call|><|tool_response>';
        return "That answers it.";
    };
    try {
        const out = await Tools.answerQuestion({ question: "compare 1678 and 254 on opr and xP", history, ctx, tier: { id: "gemma4e2b" }, isZachary: false });
        const first = seen[0];
        assert.ok(!first.messages[first.messages.length - 1].content.includes("On screen from the previous reply"), "no 'call the same tool' hint for a new question");
        assert.ok(!first.options.tools.some((d) => d.function.name === "compare_metrics"), "metric-vs-metric is not offered for two named teams");
        const repair = seen[1].messages[seen[1].messages.length - 1];
        assert.match(repair.content, /teams 1678, 254/, "the event-wide call is rejected with the named teams");
        assert.deepEqual(out.results.map((r) => r.tool), ["compare_teams"], "only the call with the named teams ran");
        assert.deepEqual(out.results[0].args.teams, [1678, 254]);
    } finally {
        AI.generate = realGenerate;
    }
});

test("wrong comparison claims are rewritten from the table, and copied display notes are removed", async () => {
    const named = { ...ctx, teams: new Map(ctx.teams), stats: new Map(ctx.stats) };
    named.stats.set(254, { ...ctx.stats.get(254), name: "Wildcard Robotics" });
    named.stats.set(1678, { ...ctx.stats.get(1678), name: "Crazy Train" });
    const out = await Tools.TOOLS.make_chart.run(named, { teams: [254, 1678], metrics: ["xP", "OPR"] }, {});
    const res = [{ tool: "make_chart", args: {}, ...out }];
    const xp = (t) => out.facts.rows.find((r) => r.team === t).xP;
    const opr = (t) => out.facts.rows.find((r) => r.team === t).OPR;
    assert.ok(xp(254) > xp(1678) && opr(254) > opr(1678), "fixture: 254 is higher on both");
    const wrong = '[Shown with this reply: table "xP, OPR" (2 rows)]\nThe chart shows both teams. Wild Card Robotics leads in the xP metric, while Crazy Train shows a stronger performance in the OPR metric.';
    const fixed = Tools.verifyAndCorrectAnswer(wrong, res, "compare 254 and 1678 on xP and OPR", named).text;
    assert.ok(!fixed.includes("[Shown with this reply"), fixed);
    assert.ok(!/Crazy Train shows a stronger/.test(fixed), fixed);
    assert.match(fixed, /254 is higher on xP .* 254 is higher on OPR/, fixed);
    const right = "Wildcard Robotics leads on xP and is also higher on OPR.";
    assert.equal(Tools.verifyAndCorrectAnswer(right, res, "q", named).text, right, "correct claims are left alone");
});

test("'make that a scatter plot' redraws the on-screen teams, not every team", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const prev = await Tools.TOOLS.make_chart.run(ctx, { teams: [254, 1678], metrics: ["xP", "OPR"] }, {});
    const history = historyWith(prev, "make_chart");
    const seen = [];
    AI.generate = async (messages, options) => {
        seen.push({ messages, options });
        if (!options.raw) return "Here it is.";
        if (seen.length === 1) return '<|tool_call>call:scatter{metric_x:<|"|>xP<|"|>,metric_y:<|"|>OPR<|"|>}<tool_call|><|tool_response>';
        if (seen.length === 2) {
            const hint = messages[messages.length - 1].content;
            const m = hint.match(/Call make_chart with (\{.*\})\./);
            return `<|tool_call>call:make_chart${m ? m[1].replace(/"([a-z_]+)":/g, "$1:").replace(/"/g, '<|"|>') : "{}"}<tool_call|><|tool_response>`;
        }
        return "Done.";
    };
    try {
        const out = await Tools.answerQuestion({ question: "make that a scatter plot", history, ctx, tier: { id: "gemma4e2b" }, isZachary: false });
        assert.match(seen[0].messages[seen[0].messages.length - 1].content, /Suggested call .*"type":"scatter"/, "the edit is suggested from the on-screen spec");
        assert.deepEqual(out.results.map((r) => r.tool), ["make_chart"], "the all-teams scatter was rejected");
        assert.equal(out.results[0].chart.type, "scatter");
        assert.deepEqual(out.results[0].chart.points.map((p) => p.team).sort((a, b) => a - b), [254, 1678]);
    } finally {
        AI.generate = realGenerate;
    }
});

test("scatter plots only the teams asked for, teams are never silently dropped, and false 'same position' claims are fixed", async () => {
    const out = await Tools.TOOLS.scatter.run(ctx, { metric_x: "OPR", metric_y: "xP", teams: [254, 1678] }, {});
    assert.deepEqual(out.chart.points.map((p) => p.team).sort((a, b) => a - b), [254, 1678]);
    assert.deepEqual(out.facts.teams_plotted.slice().sort((a, b) => a - b), [254, 1678]);
    assert.equal((await Tools.TOOLS.scatter.run(ctx, { metric_x: "OPR", metric_y: "xP" }, {})).chart.points.length, TEAMS.length, "no teams -> every team");

    const v = (tool, args) => Tools.validateCall(ctx, tool, Tools.coerceArgsDetailed(ctx, args).args).errors.join(" ");
    assert.equal(v("scatter", { metric_x: "OPR", metric_y: "xP", teams: [254, 1678] }), "");
    assert.match(v("current_rankings", { teams: [254, 1678] }), /cannot limit to specific teams.*make_chart/);
    assert.match(v("team_overview", { teams: [254] }), /takes "team", not "teams"/);

    const res = [{ tool: "scatter", args: {}, ...out }];
    const claim = "The scatter plot comparing 254 and 1678 on OPR versus xP shows that both teams occupy the exact same position on the chart, indicating identical performance metrics across these two key areas.";
    const fixed = Tools.verifyAndCorrectAnswer(claim, res, "scatter 254 and 1678 opr vs xp", ctx).text;
    assert.ok(!/same position/.test(fixed), fixed);
    assert.match(fixed, /254 is higher on (OPR|xP)/, fixed);
});
