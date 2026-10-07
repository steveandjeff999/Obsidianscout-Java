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
        epa: 80 - i * 5, opr: 60 - ((i * 7) % 12) * 4, exp: 75 - i * 3 })),
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
            AI.generate = async (messages) => { lastUser = messages[messages.length - 1].content; return reply; };
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
    assert.equal(row["EPA - xP"], Math.round((team.epa - team.exp) * 10) / 10);
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

test("drafts the code is unsure about are refined by capable models; small tiers use the draft", async () => {
    const AI = (await import(pathToFileURL(path.join(staticJs, "ai/local-ai.js")).href)).default;
    const realGenerate = AI.generate;
    const question = "make a table of teams whose auto beats their teleop";
    assert.ok(Tools.ruleRoute(question, ctx).refine);
    const refined = { action: "call", tool: "make_table", args: { columns: ["Auto points", "Teleop points"], conditions: [{ column: "Auto points - Teleop points", op: ">", value: 0 }] } };
    try {
        let calls = 0;
        AI.generate = async () => (calls++ === 0 ? JSON.stringify(refined) : "Here it is.");
        const big = await Tools.answerQuestion({ question, ctx, tier: { id: "gemma4e4b" } });
        assert.deepEqual(big.results[0].args.conditions, refined.args.conditions);

        AI.generate = async () => { throw new Error("Lite should not call the model here"); };
        const lite = await Tools.answerQuestion({ question, ctx, tier: { id: "lite" } });
        assert.equal(lite.results[0].tool, "make_table");
        assert.equal(lite.results[0].args.conditions, undefined);

        // A spec the model gets wrong falls back to the keyword draft.
        calls = 0;
        AI.generate = async () => (calls++ === 0 ? JSON.stringify({ action: "call", tool: "make_table", args: { columns: ["warp drive"] } }) : "Here it is.");
        const bad = await Tools.answerQuestion({ question, ctx, tier: { id: "gemma4e4b" } });
        assert.equal(bad.results.length, 1);
        assert.deepEqual(bad.results[0].spec.args.columns, ["Auto points", "Teleop points"]);

        // An edit the code cannot apply goes to the model together with the current spec.
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
        assert.ok(seen.includes('changing the table on screen. Its current spec: {"tool":"make_table","args":{"columns":["EPA","OPR","xP"]}}'), seen);
        assert.deepEqual(edited.results[0].table.columns.slice(2), ["xP", "OPR", "EPA"]);
    } finally {
        AI.generate = realGenerate;
    }
});

test("metrics with no data at the event do not block a table", async () => {
    const noXp = { ...ctx, metrics: ctx.metrics.filter((m) => m.id !== "ext:exp") };
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
